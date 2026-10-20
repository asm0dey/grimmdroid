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

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.work.WorkInfo
import androidx.work.workDataOf
import io.github.asm0dey.grimmdroid.account.AccountEntity
import io.github.asm0dey.grimmdroid.api.BookDetailDto
import io.github.asm0dey.grimmdroid.catalog.BookEntity
import io.github.asm0dey.grimmdroid.catalog.DownloadEntity
import io.github.asm0dey.grimmdroid.catalog.DownloadState
import io.github.asm0dey.grimmdroid.download.DownloadWorker
import io.github.asm0dey.grimmdroid.export.FolderResult
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import java.io.File
import java.io.IOException
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class DetailsViewModelSpec :
    FunSpec({
        val dispatcher = UnconfinedTestDispatcher()
        beforeTest { Dispatchers.setMain(dispatcher) }
        afterTest { Dispatchers.resetMain() }

        val account = AccountEntity(1, "https://g.example", "u", "1.0", canDownload = true, isAdmin = false, encryptedTokens = null)
        val book = BookEntity(1, 7, "Moby Dick", "H", null, null, null, null, null, null, 42, "EPUB", null, null, null, false, 1)
        val file = File.createTempFile("moby", ".epub").apply { deleteOnExit() }

        fun row(state: DownloadState) = DownloadEntity(1, 7, 42, file.name, file.path, 10, state, 0.5f)

        fun vm(
            rows: MutableStateFlow<DownloadEntity?>,
            enqueued: MutableList<Long?> = mutableListOf(),
            fetches: MutableList<Long> = mutableListOf(),
            online: MutableStateFlow<Boolean> = MutableStateFlow(true),
            work: Flow<WorkInfo?> = flowOf(null),
            accounts: Flow<List<AccountEntity>> = flowOf(listOf(account)),
            queried: MutableList<Long> = mutableListOf(),
            books: Flow<BookEntity?> = flowOf(book),
            load: suspend (Long) -> BookDetailDto = { BookDetailDto(it) },
            readAfter: MutableList<Boolean> = mutableListOf(),
            opens: MutableSharedFlow<DetailsEvent> = MutableSharedFlow(extraBufferCapacity = 1),
            pendingScope: CoroutineScope = CoroutineScope(dispatcher),
        ) = DetailsViewModel(
            1,
            7,
            accounts,
            online,
            { a, _ ->
                queried += a
                books
            },
            { _, _ -> rows },
            { _, _ -> work },
            { _, id ->
                fetches += id
                load(id)
            },
            { _, _, fileId, _, read ->
                enqueued += fileId
                readAfter += read
            },
            { _, _ -> },
            { _, _ -> },
            { _, _ -> FolderResult.NeedsFolder },
            opens,
            pendingScope,
        )

        test("share before download enqueues the primary file and shares exactly once when it lands") {
            runTest(dispatcher) {
                val rows = MutableStateFlow<DownloadEntity?>(null)
                val enqueued = mutableListOf<Long?>()
                val vm = vm(rows, enqueued)
                val events = mutableListOf<DetailsEvent>()
                backgroundScope.launch { vm.events.toList(events) }
                vm.download(null, share = true)
                enqueued shouldBe listOf(42L)
                rows.value = row(DownloadState.RUNNING)
                rows.value = row(DownloadState.DONE)
                rows.value = row(DownloadState.DONE).copy(sizeBytes = 11)
                events shouldBe listOf(DetailsEvent.Share(file, "Moby Dick"))
            }
        }

        test("read before download enqueues the primary file to read and opens it exactly once when it lands") {
            runTest(dispatcher) {
                val rows = MutableStateFlow<DownloadEntity?>(null)
                val enqueued = mutableListOf<Long?>()
                val readAfter = mutableListOf<Boolean>()
                val opens = MutableSharedFlow<DetailsEvent>(extraBufferCapacity = 1)
                val opened = mutableListOf<DetailsEvent>()
                backgroundScope.launch { opens.toList(opened) }
                val vm = vm(rows, enqueued, readAfter = readAfter, opens = opens, pendingScope = backgroundScope)
                vm.read()
                enqueued shouldBe listOf(42L)
                readAfter shouldBe listOf(true)
                rows.value = row(DownloadState.RUNNING)
                rows.value = row(DownloadState.DONE)
                rows.value = row(DownloadState.DONE).copy(sizeBytes = 11)
                opened shouldBe listOf(DetailsEvent.Read(1, 7, file))
            }
        }

        test("cancelling drops the pending read") {
            runTest(dispatcher) {
                val rows = MutableStateFlow<DownloadEntity?>(null)
                val opens = MutableSharedFlow<DetailsEvent>(extraBufferCapacity = 1)
                val opened = mutableListOf<DetailsEvent>()
                backgroundScope.launch { opens.toList(opened) }
                val vm = vm(rows, opens = opens, pendingScope = backgroundScope)
                vm.read()
                rows.value = row(DownloadState.RUNNING)
                vm.cancel()
                rows.value = row(DownloadState.DONE)
                opened shouldBe emptyList()
            }
        }

        test("open with before download opens it with another app once it lands, without the read flag") {
            runTest(dispatcher) {
                val rows = MutableStateFlow<DownloadEntity?>(null)
                val readAfter = mutableListOf<Boolean>()
                val opens = MutableSharedFlow<DetailsEvent>(extraBufferCapacity = 1)
                val opened = mutableListOf<DetailsEvent>()
                backgroundScope.launch { opens.toList(opened) }
                val vm = vm(rows, readAfter = readAfter, opens = opens, pendingScope = backgroundScope)
                vm.openWith()
                readAfter shouldBe listOf(false)
                rows.value = row(DownloadState.RUNNING)
                rows.value = row(DownloadState.DONE)
                opened shouldBe listOf(DetailsEvent.OpenWith(file))
            }
        }

        test("read on a downloaded book opens it at once without downloading") {
            runTest(dispatcher) {
                val enqueued = mutableListOf<Long?>()
                val opens = MutableSharedFlow<DetailsEvent>(extraBufferCapacity = 1)
                val opened = mutableListOf<DetailsEvent>()
                backgroundScope.launch { opens.toList(opened) }
                val vm = vm(MutableStateFlow(row(DownloadState.DONE)), enqueued, opens = opens, pendingScope = backgroundScope)
                vm.read()
                vm.openWith()
                enqueued shouldBe emptyList()
                opened shouldBe listOf(DetailsEvent.Read(1, 7, file), DetailsEvent.OpenWith(file))
            }
        }

        test("an open with nobody collecting is dropped, not delivered to a later collector") {
            runTest(dispatcher) {
                val opens = MutableSharedFlow<DetailsEvent>(extraBufferCapacity = 1)
                val vm = vm(MutableStateFlow(row(DownloadState.DONE)), opens = opens, pendingScope = backgroundScope)
                vm.read()
                val opened = mutableListOf<DetailsEvent>()
                backgroundScope.launch { opens.toList(opened) }
                opened shouldBe emptyList()
            }
        }

        test("a pending read outlives the details screen") {
            runTest(dispatcher) {
                val rows = MutableStateFlow<DownloadEntity?>(null)
                val opens = MutableSharedFlow<DetailsEvent>(extraBufferCapacity = 1)
                val opened = mutableListOf<DetailsEvent>()
                backgroundScope.launch { opens.toList(opened) }
                val store = ViewModelStore()
                val vm =
                    ViewModelProvider.create(
                        store,
                        viewModelFactory { initializer { vm(rows, opens = opens, pendingScope = backgroundScope) } },
                    )[DetailsViewModel::class]
                vm.read()
                rows.value = row(DownloadState.RUNNING)
                store.clear()
                rows.value = row(DownloadState.DONE)
                opened shouldBe listOf(DetailsEvent.Read(1, 7, file))
            }
        }

        test("a failed download drops the pending share") {
            runTest(dispatcher) {
                val rows = MutableStateFlow<DownloadEntity?>(null)
                val work = MutableStateFlow<WorkInfo?>(WorkInfo(UUID.randomUUID(), WorkInfo.State.RUNNING, emptySet()))
                val vm = vm(rows, work = work)
                val events = mutableListOf<DetailsEvent>()
                backgroundScope.launch { vm.events.toList(events) }
                vm.download(null, share = true)
                rows.value = row(DownloadState.RUNNING)
                work.value = WorkInfo(UUID.randomUUID(), WorkInfo.State.FAILED, emptySet())
                rows.value = row(DownloadState.FAILED)
                vm.state.value.failure shouldBe "other"
                rows.value = row(DownloadState.DONE)
                events shouldBe emptyList()
            }
        }

        test("detail is fetched once while online, not again on reconnect") {
            runTest(dispatcher) {
                val fetches = mutableListOf<Long>()
                val online = MutableStateFlow(false)
                val vm = vm(MutableStateFlow(null), fetches = fetches, online = online)
                vm.state.value.action shouldBe PrimaryAction.Offline
                fetches shouldBe emptyList()
                online.value = true
                online.value = false
                online.value = true
                fetches shouldBe listOf(7L)
                vm.state.value.detail shouldBe BookDetailDto(7)
            }
        }

        test("a share stays queued while nobody collects, e.g. the app is in the background") {
            runTest(dispatcher) {
                val rows = MutableStateFlow<DownloadEntity?>(null)
                val vm = vm(rows)
                vm.download(null, share = true)
                rows.value = row(DownloadState.RUNNING)
                rows.value = row(DownloadState.DONE)
                val events = mutableListOf<DetailsEvent>()
                backgroundScope.launch { vm.events.toList(events) }
                events shouldBe listOf(DetailsEvent.Share(file, "Moby Dick"))
            }
        }

        test("enqueued work stands in for a missing, failed or vanished-file row") {
            runTest(dispatcher) {
                val enqueued = WorkInfo(UUID.randomUUID(), WorkInfo.State.ENQUEUED, emptySet())
                val gone = row(DownloadState.DONE).copy(path = File(file.parentFile, "gone-${System.nanoTime()}").path)
                for (r in listOf(null, row(DownloadState.FAILED), gone)) {
                    val vm = vm(MutableStateFlow(r), work = flowOf(enqueued))
                    vm.state.value.action shouldBe PrimaryAction.Downloading(-1f)
                    vm.state.value.failure shouldBe null
                }
                // Without pending work the vanished file just offers Download again.
                vm(MutableStateFlow(gone)).state.value.action shouldBe PrimaryAction.Download(readable = true)
            }
        }

        test("a queued or running row without pending work is stale: Download, not a no-op Cancel") {
            runTest(dispatcher) {
                val done = WorkInfo(UUID.randomUUID(), WorkInfo.State.CANCELLED, emptySet())
                for (work in listOf(flowOf(null), flowOf(done))) {
                    for (state in listOf(DownloadState.QUEUED, DownloadState.RUNNING)) {
                        val vm = vm(MutableStateFlow(row(state)), work = work)
                        vm.state.value.action shouldBe PrimaryAction.Download(readable = true)
                        vm.state.value.failure shouldBe null
                    }
                }
            }
        }

        test("a failed row reports the work's error kind") {
            runTest(dispatcher) {
                val failed =
                    WorkInfo(
                        UUID.randomUUID(),
                        WorkInfo.State.FAILED,
                        emptySet(),
                        workDataOf(DownloadWorker.KEY_ERROR to DownloadWorker.ERROR_NETWORK),
                    )
                val vm = vm(MutableStateFlow(row(DownloadState.FAILED)), work = flowOf(failed))
                vm.state.value.failure shouldBe DownloadWorker.ERROR_NETWORK
                vm.state.value.action shouldBe PrimaryAction.Download(readable = true)
            }
        }

        test("a failed detail fetch is retried when the screen shows again") {
            runTest(dispatcher) {
                var fail = true
                val fetches = mutableListOf<Long>()
                val vm =
                    vm(MutableStateFlow(null), fetches = fetches, load = {
                        if (fail) throw IOException("boom")
                        BookDetailDto(it)
                    })
                vm.state.value.detail shouldBe null
                fail = false
                vm.refreshDetail()
                vm.refreshDetail()
                fetches shouldBe listOf(7L, 7L)
                vm.state.value.detail shouldBe BookDetailDto(7)
            }
        }

        test("stays on the account it was opened under") {
            runTest(dispatcher) {
                val other = account.copy(id = 2, username = "v")
                val accounts = MutableStateFlow(listOf(account, other))
                val queried = mutableListOf<Long>()
                val vm = vm(MutableStateFlow(null), accounts = accounts, queried = queried)
                accounts.value = listOf(other, account.copy(lastSyncedAt = 5))
                queried.toSet() shouldBe setOf(1L)
                vm.state.value.account
                    ?.id shouldBe 1L
                accounts.value = listOf(other)
                vm.state.value.account shouldBe null
                queried.toSet() shouldBe setOf(1L)
            }
        }

        test("a book gone from the catalogue is reported as missing") {
            runTest(dispatcher) {
                val vm = vm(MutableStateFlow(null), books = flowOf(null))
                vm.state.value.missing shouldBe true
                vm.state.value.action shouldBe null
            }
        }
    })
