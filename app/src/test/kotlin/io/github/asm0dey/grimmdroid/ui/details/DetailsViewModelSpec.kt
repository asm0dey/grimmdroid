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

import io.github.asm0dey.grimmdroid.account.AccountEntity
import io.github.asm0dey.grimmdroid.api.BookDetailDto
import io.github.asm0dey.grimmdroid.catalog.BookEntity
import io.github.asm0dey.grimmdroid.catalog.DownloadEntity
import io.github.asm0dey.grimmdroid.catalog.DownloadState
import io.github.asm0dey.grimmdroid.export.FolderResult
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import java.io.File

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
        ) = DetailsViewModel(
            7,
            flowOf(account),
            online,
            { _, _ -> flowOf(book) },
            { _, _ -> rows },
            { _, _ -> flowOf(null) },
            { _, id ->
                fetches += id
                BookDetailDto(id)
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
    })
