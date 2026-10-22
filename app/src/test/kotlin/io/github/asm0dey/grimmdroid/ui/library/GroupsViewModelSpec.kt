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

import io.github.asm0dey.grimmdroid.catalog.AuthorBook
import io.github.asm0dey.grimmdroid.catalog.BookEntity
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

private fun book(
    id: Long,
    series: String? = "S",
    library: Long = 1,
    status: String? = null,
) = BookEntity(1, id, "T$id", "A", series, id.toFloat(), library, null, null, null, null, "EPUB", null, null, null, false, 1, status)

@OptIn(ExperimentalCoroutinesApi::class)
class GroupsViewModelSpec :
    FunSpec({
        val dispatcher = UnconfinedTestDispatcher()
        beforeTest { Dispatchers.setMain(dispatcher) }
        afterTest { Dispatchers.resetMain() }

        test("a library filter lists a series with a book in it, counting all its books") {
            runTest(dispatcher) {
                val series = MutableStateFlow(listOf(book(1, status = "READ"), book(2, library = 2), book(3, series = "Other")))
                val vm = GroupsViewModel(series, MutableStateFlow(emptyList()))
                backgroundScope.launch { vm.listing.collect {} }
                vm.mode.value = LibraryMode.SERIES
                vm.libraryId.value = 2
                vm.listing.value.groups!!
                    .map { Triple(it.name, it.readCount, it.total) } shouldBe listOf(Triple("S", 1, 2))
            }
        }

        test("not started only hides started series and keeps one with only dismissed and unread books") {
            runTest(dispatcher) {
                val series =
                    MutableStateFlow(
                        listOf(
                            book(1, status = "READ"),
                            book(2),
                            book(3, series = "D", status = "ABANDONED"),
                            book(4, series = "D"),
                        ),
                    )
                val vm = GroupsViewModel(series, MutableStateFlow(emptyList()))
                backgroundScope.launch { vm.listing.collect {} }
                vm.mode.value = LibraryMode.SERIES
                vm.listing.value.groups!!
                    .map { it.name } shouldBe listOf("D", "S")
                vm.notStartedOnly.value = true
                vm.listing.value.groups!!
                    .map { it.name } shouldBe listOf("D")
            }
        }

        test("authors come from the pairs with full names, sorted case-insensitively with the exact name as tie-break") {
            runTest(dispatcher) {
                val pairs =
                    MutableStateFlow(
                        listOf(
                            AuthorBook("b Writer", book(1)),
                            AuthorBook("A Writer", book(2)),
                            AuthorBook("a Writer", book(3)),
                            AuthorBook("A Writer", book(4, status = "READ")),
                        ),
                    )
                val vm = GroupsViewModel(MutableStateFlow(emptyList()), pairs)
                backgroundScope.launch { vm.listing.collect {} }
                vm.mode.value = LibraryMode.AUTHORS
                vm.listing.value.groups!!
                    .map { Triple(it.name, it.readCount, it.total) } shouldBe
                    listOf(Triple("A Writer", 1, 2), Triple("a Writer", 0, 1), Triple("b Writer", 0, 1))
            }
        }

        test("switching from authors to series never lists the authors as series") {
            runTest(dispatcher) {
                val series = MutableSharedFlow<List<BookEntity>>()
                val vm = GroupsViewModel(series, MutableStateFlow(listOf(AuthorBook("Writer", book(1)))))
                backgroundScope.launch { vm.listing.collect {} }
                vm.mode.value = LibraryMode.AUTHORS
                vm.listing.value.groups!!
                    .map { it.name } shouldBe listOf("Writer")
                vm.mode.value = LibraryMode.SERIES
                vm.listing.value shouldBe GroupListing(LibraryMode.SERIES, null)
                series.emit(listOf(book(2)))
                vm.listing.value.groups!!
                    .map { it.name } shouldBe listOf("S")
            }
        }

        test("books mode lists no groups") {
            runTest(dispatcher) {
                val vm = GroupsViewModel(MutableStateFlow(listOf(book(1))), MutableStateFlow(emptyList()))
                backgroundScope.launch { vm.listing.collect {} }
                vm.listing.value.groups shouldBe emptyList()
            }
        }
    })
