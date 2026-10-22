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
package io.github.asm0dey.grimmdroid.ui.group

import io.github.asm0dey.grimmdroid.catalog.BookEntity
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

private fun book(
    id: Long,
    series: String? = "S",
    number: Float? = id.toFloat(),
    status: String? = null,
) = BookEntity(1, id, "T$id", "A", series, number, null, null, null, null, null, "EPUB", null, null, null, false, 1, status)

@OptIn(ExperimentalCoroutinesApi::class)
class GroupViewModelSpec :
    FunSpec({
        val dispatcher = UnconfinedTestDispatcher()
        beforeTest { Dispatchers.setMain(dispatcher) }
        afterTest { Dispatchers.resetMain() }

        test("a series page is one section in series order with the next book and the read count") {
            runTest(dispatcher) {
                val source = MutableStateFlow(listOf(book(2), book(1, status = "READ")))
                val vm = GroupViewModel(source, "S", byAuthor = false)
                backgroundScope.launch { vm.page.collect {} }
                val page = vm.page.value!!
                page.title shouldBe "S"
                page.readCount shouldBe 1
                page.total shouldBe 2
                page.sections.size shouldBe 1
                page.sections[0].seriesName shouldBe "S"
                page.sections[0].books.map { it.id } shouldBe listOf(1L, 2L)
                page.sections[0].next?.id shouldBe 2L

                source.value = listOf(book(2, status = "READ"), book(1, status = "READ"))
                vm.page.value!!
                    .sections[0]
                    .next shouldBe null
                vm.page.value!!.readCount shouldBe 2
            }
        }

        test("an author page has a section per series by name, case-insensitively, then the books outside a series") {
            runTest(dispatcher) {
                val source =
                    MutableStateFlow(
                        listOf(
                            book(1, series = "B"),
                            book(2, series = null, number = null),
                            book(3, series = "a"),
                            book(4, series = null, number = null),
                        ),
                    )
                val vm = GroupViewModel(source, "A", byAuthor = true)
                backgroundScope.launch { vm.page.collect {} }
                val sections = vm.page.value!!.sections
                sections.map { it.seriesName } shouldBe listOf("a", "B", null)
                sections.map { s -> s.books.map { it.id } } shouldBe listOf(listOf(3L), listOf(1L), listOf(2L, 4L))
                sections.map { it.next?.id } shouldBe listOf(3L, 1L, null)
                vm.page.value!!.total shouldBe 4
            }
        }
    })
