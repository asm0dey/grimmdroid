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
            account: MutableStateFlow<Long?> = MutableStateFlow(1),
            save: suspend (Long, File) -> FolderResult = { _, file ->
                saved += file.path
                result
            },
            delete: suspend (Long, Long) -> Unit = { a, b -> deleted += a to b },
        ) = DownloadsViewModel(account, { flowOf(all) }, delete, save)

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

        test("switching account clears the selection") {
            runTest(dispatcher) {
                val account = MutableStateFlow<Long?>(1)
                val vm = vm(account = account)
                backgroundScope.launch { vm.items.collect {} }
                vm.toggle(1)
                account.value = 2
                vm.selection.value shouldBe emptySet()
            }
        }

        test("FolderLost and thrown errors are reported and keep the selection") {
            runTest(dispatcher) {
                for (fail in listOf<suspend (Long, File) -> FolderResult>(
                    { _, _ -> FolderResult.FolderLost },
                    { _, _ -> throw java.io.IOException("x") },
                    { _, _ -> throw SecurityException("x") },
                )) {
                    val vm = vm(save = fail)
                    backgroundScope.launch { vm.items.collect {} }
                    val got = mutableListOf<Result<FolderResult>>()
                    backgroundScope.launch { vm.saveResults.collect { got += it } }
                    vm.toggle(2)
                    vm.saveSelected()
                    got.size shouldBe 1
                    (got[0].getOrNull() == FolderResult.FolderLost || got[0].isFailure) shouldBe true
                    vm.selection.value shouldBe setOf(2L)
                }
            }
        }

        test("a partial save failure drops only the saved books from the selection") {
            runTest(dispatcher) {
                val vm = vm(save = { _, f -> if (f.path.contains("/2/")) throw java.io.IOException("x") else FolderResult.Saved("B") })
                backgroundScope.launch { vm.items.collect {} }
                vm.toggle(1)
                vm.toggle(2)
                vm.toggle(3)
                vm.saveSelected()
                vm.selection.value shouldBe setOf(2L, 3L)
            }
        }

        test("saving or deleting with nothing selected does nothing") {
            runTest(dispatcher) {
                val saved = mutableListOf<String>()
                val deleted = mutableListOf<Pair<Long, Long>>()
                val vm = vm(deleted, saved)
                backgroundScope.launch { vm.items.collect {} }
                val got = mutableListOf<Result<FolderResult>>()
                backgroundScope.launch { vm.saveResults.collect { got += it } }
                vm.saveSelected()
                vm.deleteSelected()
                got.size shouldBe 0
                saved.size shouldBe 0
                deleted.size shouldBe 0
            }
        }

        test("a failing delete is reported and the failed book stays selected") {
            runTest(dispatcher) {
                val vm = vm(delete = { _, b -> if (b == 2L) throw SecurityException("x") })
                backgroundScope.launch { vm.items.collect {} }
                var failures = 0
                backgroundScope.launch { vm.deleteFailed.collect { failures++ } }
                vm.toggle(1)
                vm.toggle(2)
                vm.deleteSelected()
                failures shouldBe 1
                vm.selection.value shouldBe setOf(2L)
            }
        }
    })
