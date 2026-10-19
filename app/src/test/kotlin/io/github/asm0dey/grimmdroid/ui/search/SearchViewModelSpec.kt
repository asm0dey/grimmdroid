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
package io.github.asm0dey.grimmdroid.ui.search

import androidx.paging.PagingSource
import androidx.paging.PagingState
import io.github.asm0dey.grimmdroid.catalog.BookRow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

internal class EmptySource : PagingSource<Int, BookRow>() {
    override suspend fun load(params: LoadParams<Int>) = LoadResult.Page<Int, BookRow>(emptyList(), null, null)

    override fun getRefreshKey(state: PagingState<Int, BookRow>): Int? = null
}

@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelSpec :
    FunSpec({
        test("each token becomes a quoted prefix term") { ftsQuery("moby di") shouldBe "\"moby*\" \"di*\"" }

        test("quotes, operators and syntax characters never reach MATCH unquoted") {
            ftsQuery("\"*") shouldBe ""
            ftsQuery("-") shouldBe ""
            ftsQuery("") shouldBe ""
            ftsQuery("a:b") shouldBe "\"a*\" \"b*\""
            ftsQuery("NOT") shouldBe "\"NOT*\""
            ftsQuery("(x") shouldBe "\"x*\""
            ftsQuery("  Война^и\"мир ") shouldBe "\"Война*\" \"и*\" \"мир*\""
        }

        val dispatcher = StandardTestDispatcher()
        beforeTest { Dispatchers.setMain(dispatcher) }
        afterTest { Dispatchers.resetMain() }

        test("debounces 150 ms, skips empty queries and follows the current account") {
            runTest(dispatcher) {
                val account = MutableStateFlow<Long?>(1)
                val calls = mutableListOf<Pair<Long, String>>()
                val vm =
                    SearchViewModel(account) { id, fts ->
                        calls += id to fts
                        EmptySource()
                    }
                backgroundScope.launch { vm.results.collect {} }
                vm.query.value = "\"*"
                advanceUntilIdle()
                calls.shouldBeEmpty()

                vm.query.value = "moby di"
                advanceTimeBy(149)
                runCurrent()
                calls.shouldBeEmpty()
                advanceTimeBy(2)
                runCurrent()
                calls shouldBe listOf(1L to "\"moby*\" \"di*\"")

                account.value = 2
                advanceUntilIdle()
                calls.last() shouldBe (2L to "\"moby*\" \"di*\"")
            }
        }
    })
