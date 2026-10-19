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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
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
            { _, _, fileId, _ -> enqueued += fileId },
            { _, _ -> },
            { _, _ -> },
            { _, _ -> FolderResult.NeedsFolder },
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

        test("a failed download drops the pending share") {
            runTest(dispatcher) {
                val rows = MutableStateFlow<DownloadEntity?>(null)
                val vm = vm(rows)
                val events = mutableListOf<DetailsEvent>()
                backgroundScope.launch { vm.events.toList(events) }
                vm.download(null, share = true)
                rows.value = row(DownloadState.RUNNING)
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
                vm(MutableStateFlow(gone)).state.value.action shouldBe PrimaryAction.Download
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
                vm.state.value.action shouldBe PrimaryAction.Download
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
