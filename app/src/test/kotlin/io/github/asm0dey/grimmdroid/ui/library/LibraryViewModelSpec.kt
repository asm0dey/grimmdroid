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
package io.github.asm0dey.grimmdroid.ui.library

import io.github.asm0dey.grimmdroid.catalog.BookSort
import io.github.asm0dey.grimmdroid.catalog.LibraryEntity
import io.github.asm0dey.grimmdroid.settings.Layout
import io.github.asm0dey.grimmdroid.ui.search.EmptySource
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModelSpec :
    FunSpec({
        val dispatcher = StandardTestDispatcher()
        beforeTest { Dispatchers.setMain(dispatcher) }
        afterTest { Dispatchers.resetMain() }

        fun vm(
            scope: ShelfScope? = null,
            account: MutableStateFlow<Long?> = MutableStateFlow(1),
            layout: MutableStateFlow<Layout> = MutableStateFlow(Layout.GRID),
        ) = LibraryViewModel(
            scope,
            account,
            { EmptySource() },
            { id -> flowOf(listOf(LibraryEntity(id, 10 * id, "L$id", 1))) },
            layout,
            { layout.value = it },
        )

        test("filters land in the query") {
            runTest(dispatcher) {
                val vm = vm()
                advanceUntilIdle()
                vm.setLibrary(3)
                vm.setSort(BookSort.TITLE)
                vm.setFormats(setOf("EPUB"))
                vm.setDownloadedOnly(true)
                vm.query.value.libraryId shouldBe 3
                vm.query.value.sort shouldBe BookSort.TITLE
                vm.query.value.formats shouldBe setOf("EPUB")
                vm.query.value.downloadedOnly shouldBe true
                vm.setLibrary(null)
                vm.query.value.libraryId
                    .shouldBeNull()
            }
        }

        test("shelf scope sets shelfId, magic scope sets magicShelfId") {
            runTest(dispatcher) {
                val shelf = vm(ShelfScope(7, magic = false))
                val magic = vm(ShelfScope(8, magic = true))
                advanceUntilIdle()
                shelf.query.value.shelfId shouldBe 7
                shelf.query.value.magicShelfId
                    .shouldBeNull()
                magic.query.value.magicShelfId shouldBe 8
                magic.query.value.shelfId
                    .shouldBeNull()
            }
        }

        test("switching account restarts the query for the new account, keeping scope and sort") {
            runTest(dispatcher) {
                val account = MutableStateFlow<Long?>(1)
                val vm = vm(ShelfScope(7, magic = false), account)
                advanceUntilIdle()
                vm.query.value.accountId shouldBe 1
                vm.libraries.value.map { it.id } shouldBe listOf(10L)
                vm.setLibrary(10)
                vm.setSort(BookSort.AUTHOR)
                account.value = 2
                advanceUntilIdle()
                vm.query.value.accountId shouldBe 2
                vm.query.value.libraryId
                    .shouldBeNull()
                vm.query.value.shelfId shouldBe 7
                vm.query.value.sort shouldBe BookSort.AUTHOR
                vm.libraries.value.map { it.id } shouldBe listOf(20L)
            }
        }

        test("layout is read from and saved to settings") {
            runTest(dispatcher) {
                val stored = MutableStateFlow(Layout.LIST)
                val vm = vm(layout = stored)
                advanceUntilIdle()
                vm.layout.value shouldBe Layout.LIST
                vm.setLayout(Layout.GRID)
                advanceUntilIdle()
                stored.value shouldBe Layout.GRID
                vm.layout.value shouldBe Layout.GRID
            }
        }
    })
