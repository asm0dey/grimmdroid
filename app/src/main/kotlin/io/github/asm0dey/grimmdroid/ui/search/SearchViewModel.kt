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

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingSource
import androidx.paging.cachedIn
import io.github.asm0dey.grimmdroid.AppContainer
import io.github.asm0dey.grimmdroid.catalog.BookRow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

private val nonWord = Regex("[^\\p{L}\\p{M}\\p{N}]+")

/**
 * A safe FTS4 prefix query: each word becomes `"word*"` (FTS4 reads a star only inside the quotes; `"word"*` is FTS5). Everything but letters and digits is dropped, so quotes,
 * `-`, `:`, `*`, `(`, `^` can't reach MATCH, and AND/OR/NOT/NEAR are only ever quoted terms. Empty means "don't query".
 */
fun ftsQuery(input: String): String = input.split(nonWord).filter { it.isNotEmpty() }.joinToString(" ") { "\"$it*\"" }

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class SearchViewModel(
    accountId: Flow<Long?>,
    search: (accountId: Long, ftsQuery: String) -> PagingSource<Int, BookRow>,
) : ViewModel() {
    val query = MutableStateFlow("")

    val results: Flow<PagingData<BookRow>> =
        combine(accountId.filterNotNull().distinctUntilChanged(), query.debounce(150).map(::ftsQuery).distinctUntilChanged()) { id, fts ->
            id to fts
        }.flatMapLatest { (id, fts) ->
            if (fts.isEmpty()) flowOf(PagingData.empty()) else Pager(PagingConfig(pageSize = 50)) { search(id, fts) }.flow
        }.cachedIn(viewModelScope)

    companion object {
        fun factory(container: AppContainer) =
            viewModelFactory {
                initializer {
                    SearchViewModel(container.accounts.current.map { it?.id }, container.db.catalogDao()::search)
                }
            }
    }
}
