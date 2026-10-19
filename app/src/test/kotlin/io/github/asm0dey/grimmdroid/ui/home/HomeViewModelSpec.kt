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
package io.github.asm0dey.grimmdroid.ui.home

import io.github.asm0dey.grimmdroid.R
import io.github.asm0dey.grimmdroid.catalog.BookEntity
import io.github.asm0dey.grimmdroid.catalog.DownloadEntity
import io.github.asm0dey.grimmdroid.catalog.DownloadState
import io.github.asm0dey.grimmdroid.catalog.DownloadWithBook
import io.github.asm0dey.grimmdroid.ui.nav.Downloads
import io.github.asm0dey.grimmdroid.ui.nav.Library
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

private fun book(id: Long) = BookEntity(1, id, "T$id", "A", null, null, null, null, null, 40f, null, "EPUB", null, null, null, false, 1)

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelSpec :
    FunSpec({
        val dispatcher = UnconfinedTestDispatcher()
        beforeTest { Dispatchers.setMain(dispatcher) }
        afterTest { Dispatchers.resetMain() }

        test("rows follow the current account; empty rows are hidden except On this phone") {
            runTest(dispatcher) {
                val account = MutableStateFlow<Long?>(1)
                val vm =
                    HomeViewModel(
                        account,
                        { id -> flowOf(if (id == 1L) listOf(book(1)) else emptyList()) },
                        { id -> flowOf(if (id == 1L) listOf(book(2), book(1)) else emptyList()) },
                        { id ->
                            flowOf(
                                if (id == 1L) {
                                    listOf(DownloadWithBook(DownloadEntity(1, 2, 0, "f", "p", 1, DownloadState.DONE, 1f), book(2)))
                                } else {
                                    emptyList()
                                },
                            )
                        },
                    )
                backgroundScope.launch { vm.rows.collect {} }
                vm.rows.value.map { it.title } shouldBe
                    listOf(R.string.continue_reading, R.string.recently_added, R.string.on_this_phone)
                vm.rows.value.map { it.seeAll } shouldBe listOf(Library, Library, Downloads)
                vm.rows.value[2]
                    .books
                    .map { it.book.id to it.downloaded } shouldBe listOf(2L to true)
                account.value = 2
                vm.rows.value.map { it.title } shouldBe listOf(R.string.on_this_phone)
                vm.rows.value[0].books shouldBe emptyList()
            }
        }
    })
