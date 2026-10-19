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

import io.github.asm0dey.grimmdroid.catalog.BookEntity
import io.github.asm0dey.grimmdroid.catalog.DownloadEntity
import io.github.asm0dey.grimmdroid.catalog.DownloadState
import io.github.asm0dey.grimmdroid.catalog.DownloadWithBook
import io.github.asm0dey.grimmdroid.export.FolderResult
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import java.io.File

private fun item(
    id: Long,
    size: Long,
) = DownloadWithBook(
    DownloadEntity(1, id, 0, "f$id.epub", "/books/$id/f$id.epub", size, DownloadState.DONE, 1f),
    BookEntity(1, id, "T$id", "A", null, null, null, null, null, null, null, "EPUB", null, null, null, false, 1),
)

@OptIn(ExperimentalCoroutinesApi::class)
class DownloadsViewModelSpec :
    FunSpec({
        val dispatcher = UnconfinedTestDispatcher()
        beforeTest { Dispatchers.setMain(dispatcher) }
        afterTest { Dispatchers.resetMain() }

        val all = listOf(item(1, 100), item(2, 250), item(3, 5))

        fun vm(
            deleted: MutableList<Pair<Long, Long>> = mutableListOf(),
            saved: MutableList<String> = mutableListOf(),
            result: FolderResult = FolderResult.Saved("Books"),
        ) = DownloadsViewModel(
            MutableStateFlow<Long?>(1),
            { flowOf(all) },
            { account, book -> deleted += account to book },
            { _, file ->
                saved += file.path
                result
            },
        )

        test("totalBytes sums sizeBytes") {
            runTest(dispatcher) {
                val vm = vm()
                backgroundScope.launch { vm.totalBytes.collect {} }
                vm.totalBytes.value shouldBe 355L
            }
        }

        test("deleteSelected deletes exactly the selected books and clears the selection") {
            runTest(dispatcher) {
                val deleted = mutableListOf<Pair<Long, Long>>()
                val vm = vm(deleted)
                backgroundScope.launch { vm.items.collect {} }
                vm.toggle(1)
                vm.toggle(3)
                vm.toggle(2)
                vm.toggle(2) // toggled back off
                vm.selection.value shouldBe setOf(1L, 3L)
                vm.deleteSelected()
                deleted shouldBe listOf(1L to 1L, 1L to 3L)
                vm.selection.value shouldBe emptySet()
            }
        }

        test("saveSelected saves the selected files and clears the selection once saved") {
            runTest(dispatcher) {
                val saved = mutableListOf<String>()
                val vm = vm(saved = saved)
                backgroundScope.launch { vm.items.collect {} }
                vm.toggle(2)
                vm.saveSelected()
                saved shouldBe listOf(File("/books/2/f2.epub").path)
                vm.selection.value shouldBe emptySet()
            }
        }

        test("a missing export folder keeps the selection so the save can be retried after picking one") {
            runTest(dispatcher) {
                val vm = vm(result = FolderResult.NeedsFolder)
                backgroundScope.launch { vm.items.collect {} }
                vm.toggle(2)
                vm.saveSelected()
                vm.selection.value shouldBe setOf(2L)
            }
        }
    })
